package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.entity.TalkTopic;
import com.jrpetty.mcassistant.net.FolkProfileAskPayload;
import com.jrpetty.mcassistant.net.FolkTalkPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * [folk-ui] One folk as a person: its profile. A header with the folk itself (its model, turning to the mouse, when it
 * is about; a portrait frame when it is not), its name and nickname, age, trade and level, its mood and its four needs
 * as bars, and what it is thinking; then five tabs:
 * <ul>
 * <li><b>About</b>: its kind, traits, quirk, dream and goals (each with how far on), fears, likes, hobby, keepsake, story;</li>
 * <li><b>Life</b>: its family tree, partner and children, its home with its address and the sign by its door, its friends
 *     and rivals and how warm, and what it thinks of you;</li>
 * <li><b>Work</b>: its trade, level and experience, knacks, wage, record and output, what it has in hand and is doing now,
 *     its tools and kit (and the career the old record screen showed);</li>
 * <li><b>Day</b>: today's plan as a timeline (work, break, meals, free time, sleep) with now marked on it and set beside
 *     what it is really doing; its memories;</li>
 * <li><b>Belongings</b>: what it carries and wears, its purse and net worth laid out, the comforts of its home.</li>
 * </ul>
 *
 * <p>Opened from the talk screen (its Profile button, and its Work button), from a folk's name anywhere a name is a link
 * (the town's books, the card: FolkLinks), and from a row of the board's Folk page. The server sends the profile
 * (entity/FolkProfile) when asked; the screen asks again every three seconds while it is open, and never once it is
 * shut. Esc goes back to the screen it was opened from.
 *
 * <p>Cost: the page is laid out once, when the profile arrives, the tab changes or the window is resized (wrapped text,
 * item stacks, positions: {@link Block}s); a frame draws only the blocks in view, from what was laid out, and allocates
 * nothing but what vanilla's own item and entity rendering does.
 */
public class FolkProfileScreen extends Screen {

    /** The tabs, as the server's FolkProfile.TABS names them, and as shown. */
    private static final String[] TAB_IDS = { "about", "life", "work", "day", "things" };
    private static final String[] TAB_NAMES = { "About", "Life", "Work", "Day", "Belongings" };
    /** The same, short, for a narrow window. */
    private static final String[] TAB_SHORT = { "About", "Life", "Work", "Day", "Owns" };
    /** How often the open screen asks for a fresh profile, in client ticks (three seconds). */
    private static final int REFRESH_TICKS = 60;
    /** Within this distance the folk can be talked to (the server's talk range). */
    private static final double TALK_RANGE = 8.0;

    private static final int MAX_W = 460, MAX_H = 320, MIN_W = 240, MIN_H = 180;
    private static final int HEADER_H = 62, TABS_H = 18, FOOT_H = 24, PORTRAIT_W = 44;
    /** The folk in its portrait: how big (a grown folk some sixty pixels tall), and how far below the frame its box
     *  reaches, so the frame holds its head and shoulders. */
    private static final int PORTRAIT_SCALE = 34, PORTRAIT_DROP = 40;
    /** The label column of a fact row, and the need meters' label column. */
    private static final int KEY_W = 86, NEED_LABEL_W = 52;

    /** The colours of the day's stretches (FolkSchedule.Kind), readable side by side on the timeline. */
    private static final int C_SLEEP = 0xFF4A5A86, C_WORK = 0xFFB9822B, C_BREAK = 0xFF79A85A, C_FREE = 0xFF5E9E9A,
        C_SCHOOL = 0xFF8C6BB0, C_WATCH = 0xFF6A6F78, C_ILL = 0xFFB0554A;

    // ------------------------------------------------------------------ opening

    /** The profile asked for and not yet come: whose, which tab to open at, and the screen to go back to. */
    @Nullable private static UUID pendingId;
    @Nullable private static String pendingTab;
    @Nullable private static Screen pendingParent;

    /** Ask the server for this folk's profile; it opens when it comes, at {@code tab} (an id: about, life, ...), and
     *  Esc goes back to {@code parent}. */
    public static void open(UUID folk, @Nullable String tab, @Nullable Screen parent) {
        pendingId = folk;
        pendingTab = tab;
        pendingParent = parent;
        PacketDistributor.sendToServer(new FolkProfileAskPayload(folk, true));
    }

    /** As {@link #open}, for a folk in view by its entity id (the talk screen knows its folk that way). */
    public static void openEntity(int entityId, @Nullable String tab, @Nullable Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(entityId);
        if (e != null) open(e.getUUID(), tab, parent);
    }

    /** A profile from the server: open it (when asked to), or refresh the one up, if it is this folk's. */
    public static void receive(CompoundTag data, boolean open) {
        Minecraft mc = Minecraft.getInstance();
        if (data.getBoolean("close")) {                                       // the server's word to shut it
            if (mc.screen instanceof FolkProfileScreen up) up.onClose();
            return;
        }
        if (!data.hasUUID("uuid")) return;
        UUID id = data.getUUID("uuid");
        if (mc.screen instanceof FolkProfileScreen up && up.folk.equals(id)) {
            up.update(data);
            if (data.contains("tab")) up.tab = tabOf(data.getString("tab"), up.tab);
            return;
        }
        // Opened by asking (open), or sent by the server unasked (the profile command): never a stale answer for another.
        if (!open || pendingId != null && !id.equals(pendingId)) return;
        FolkProfileScreen s = new FolkProfileScreen(id, data, id.equals(pendingId) ? pendingParent : mc.screen instanceof FolkProfileScreen ? null : mc.screen);
        s.tab = tabOf(data.contains("tab") ? data.getString("tab") : pendingTab, 0);
        pendingId = null;
        pendingParent = null;
        mc.setScreen(s);
    }

    private static int tabOf(@Nullable String id, int otherwise) {
        if (id != null && id.equalsIgnoreCase("belongings")) id = "things";
        if (id != null) for (int i = 0; i < TAB_IDS.length; i++) if (TAB_IDS[i].equalsIgnoreCase(id)) return i;
        return otherwise;
    }

    // ------------------------------------------------------------------ state

    private final UUID folk;
    @Nullable private final Screen parent;
    private CompoundTag data;
    private int tab, left, top, w, h, ticks;
    private final UiStyle.Scroll scroll = new UiStyle.Scroll();
    /** The page as laid out (see the class note), its width and tab when laid out, and its height. */
    private final List<Block> blocks = new ArrayList<>();
    private int laidW = -1, laidTab = -1, laidEpoch = -1, pageH;
    /** The header's words, made when the profile arrives (not every frame). */
    private String headName = "", headSub = "", headThought = "", headMood = "", portraitLetter = "?";
    private List<FormattedCharSequence> thoughtLines = List.of();
    private int[] needs = new int[4];
    private int mood, jobColour;
    private String moodTip = "";

    private FolkProfileScreen(UUID folk, CompoundTag data, @Nullable Screen parent) {
        super(Component.literal(data.getCompound("head").getString("name")));
        this.folk = folk;
        this.parent = parent;
        this.data = data;
        readHead();
    }

    private void update(CompoundTag fresh) {
        this.data = fresh;
        readHead();
        laidW = -1;                                                       // lay the page out again, keeping the scroll
    }

    private void readHead() {
        CompoundTag hd = data.getCompound("head");
        String nick = hd.getString("nick");
        headName = hd.getString("name") + (nick.isEmpty() ? "" : "  “" + nick + "”");
        portraitLetter = hd.getString("name").isEmpty() ? "?" : hd.getString("name").substring(0, 1).toUpperCase(Locale.ROOT);
        String trade = hd.getString("trade");
        headSub = (hd.getBoolean("elder") ? "Elder · " : "") + trade
            + (hd.getBoolean("child") ? "" : " · level " + hd.getInt("level")) + " · " + hd.getInt("years")
            + (hd.getInt("years") == 1 ? " year" : " years") + (hd.getString("town").isEmpty() ? "" : " · " + hd.getString("town"));
        headThought = hd.getString("thought").isEmpty() ? "" : "“" + hd.getString("thought") + "”";
        mood = hd.getInt("mood");
        headMood = capital(hd.getString("mood_word")) + " " + mood;
        StringBuilder tip = new StringBuilder("Mood ").append(mood).append(" of 100: ").append(hd.getString("mood_word")).append('.');
        ListTag why = hd.getList("mood_why", Tag.TAG_STRING);
        for (int i = 0; i < why.size(); i++) tip.append('\n').append("• ").append(why.getString(i));
        moodTip = tip.toString();
        int[] n = hd.getIntArray("needs");
        needs = n.length == 4 ? n : new int[4];
        jobColour = Ui.job(hd.getInt("ordinal"));
        thoughtLines = List.of();
    }

    @Override
    protected void init() {
        w = Math.max(Math.min(MIN_W, width - 8), Math.min(MAX_W, width - 2 * UiStyle.S2));
        h = Math.max(Math.min(MIN_H, height - 8), Math.min(MAX_H, height - 2 * UiStyle.S2));
        left = (width - w) / 2;
        top = (height - h) / 2;
        laidW = -1;
        thoughtLines = List.of();                                         // wrapped again for the new width
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) PacketDistributor.sendToServer(new FolkProfileAskPayload(folk, false));
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    // ------------------------------------------------------------------ geometry

    private int pageX() { return left + UiStyle.S2; }
    private int pageY() { return top + HEADER_H + TABS_H + UiStyle.S1; }
    private int pageW() { return w - 2 * UiStyle.S2 - 4; }                   // room for the scroll bar
    private int pageH() { return top + h - FOOT_H - pageY() - UiStyle.S1; }

    @Nullable
    private LivingEntity entity() {
        if (minecraft == null || minecraft.level == null) return null;
        Entity e = minecraft.level.getEntity(data.getInt("entity"));
        return e instanceof LivingEntity l && l.isAlive() && e.getUUID().equals(folk) ? l : null;
    }

    private boolean nearEnough() {
        LivingEntity e = entity();
        return e != null && minecraft != null && minecraft.player != null && e.distanceToSqr(minecraft.player) <= TALK_RANGE * TALK_RANGE;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float partialTick) {
        super.render(g, mx, my, partialTick);
        if (laidW != pageW() || laidTab != tab || laidEpoch != TextCache.epoch()) layout();
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        String tip = header(g, mx, my);
        String t2 = tabs(g, mx, my);
        if (t2 != null) tip = t2;
        String t3 = page(g, mx, my);
        if (t3 != null) tip = t3;
        String t4 = footer(g, mx, my);
        if (t4 != null) tip = t4;
        if (tip != null) UiStyle.tooltip(g, font, tip, mx, my, 220);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float partialTick) {
        g.fill(0, 0, width, height, UiStyle.BACKDROP);
    }

    /** The header band: the folk, its name and the rest, its mood and needs. Returns a tooltip, or null. */
    @Nullable
    private String header(GuiGraphics g, int mx, int my) {
        UiStyle.headerBand(g, left, top, w, HEADER_H);
        int px = left + UiStyle.S2, py = top + 5, ph = HEADER_H - 10;
        UiStyle.well(g, px, py, PORTRAIT_W, ph, 0xFF8FA3B5);
        LivingEntity e = entity();
        if (e != null) {
            g.enableScissor(px + 1, py + 1, px + PORTRAIT_W - 1, py + ph - 1);
            // Head and shoulders: drawn large, the box reaching below the frame so the face sits in it.
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, px, py, px + PORTRAIT_W, py + ph + PORTRAIT_DROP, PORTRAIT_SCALE, 0.0625F, mx, my, e);
            g.disableScissor();
        } else {
            g.fill(px + 1, py + ph - 10, px + PORTRAIT_W - 1, py + ph - 1, jobColour);
            g.pose().pushPose();
            g.pose().translate(px + PORTRAIT_W / 2F, py + 10F, 0);
            g.pose().scale(2F, 2F, 1F);
            g.drawString(font, portraitLetter, -font.width(portraitLetter) / 2, 0, UiStyle.TEXT_ON_DARK, true);
            g.pose().popPose();
        }
        g.renderOutline(px, py, PORTRAIT_W, ph, UiStyle.EDGE);
        boolean narrow = w < 360;
        int needsW = narrow ? 0 : 118;
        int tx = px + PORTRAIT_W + UiStyle.S2, tw = left + w - UiStyle.S2 - needsW - (needsW > 0 ? UiStyle.S2 : 0) - tx;
        UiStyle.title(g, font, UiStyle.clip(font, headName, tw - 8), tx, top + 6);
        UiStyle.text(g, font, headSub, tx, top + 18, tw, UiStyle.TEXT_MUTED);
        if (thoughtLines.isEmpty() && !headThought.isEmpty()) thoughtLines = TextCache.splitPlain(font, headThought, tw);
        for (int i = 0; i < Math.min(2, thoughtLines.size()); i++) {
            g.drawString(font, thoughtLines.get(i), tx, top + 31 + i * UiStyle.LINE, UiStyle.INFO, false);
        }
        String tip = null;
        if (inside(mx, my, tx, top + 30, tw, 22) && thoughtLines.size() > 2) tip = headThought;
        if (needsW > 0) {
            int nx = left + w - UiStyle.S2 - needsW;
            int pillW = UiStyle.badge(g, font, headMood, nx, top + 5, UiStyle.levelFill(mood, 60, 40));
            if (inside(mx, my, nx, top + 5, pillW, 11)) tip = moodTip;
            for (int i = 0; i < 4; i++) {
                int ny = top + 19 + i * UiStyle.LINE;
                UiStyle.meter(g, font, NEED_NAMES[i], needs[i], nx, ny, needsW, NEED_LABEL_W);
                if (inside(mx, my, nx, ny, needsW, UiStyle.LINE)) tip = NEED_TIPS[i] + " " + needs[i] + " of 100.";
            }
        } else {
            int pillW = UiStyle.badge(g, font, headMood, tx + tw - font.width(headMood) - 6, top + 6, UiStyle.levelFill(mood, 60, 40));
            if (inside(mx, my, tx + tw - pillW, top + 6, pillW, 11)) tip = moodTip;
        }
        return tip;
    }

    private static final String[] NEED_NAMES = { "Food", "Rest", "Company", "Comfort" };
    private static final String[] NEED_TIPS = {
        "Food: its meals today, and how long since it last ate. Hungry, it goes to the stores.",
        "Rest: a night in its own bed, and how long it has been up. Worn out, it goes to bed an hour early.",
        "Company: its partner, its friends, and when it last had somebody to talk to. Lonely, it looks for company of an evening.",
        "Comfort: a bed and a house of its own, the comforts in it, its health and the weather. Low, it spends its savings on its home first." };

    @Nullable
    private String tabs(GuiGraphics g, int mx, int my) {
        int y = top + HEADER_H + 2, x = left + UiStyle.S2;
        int each = Math.min(78, (w - 2 * UiStyle.S2) / TAB_NAMES.length);
        for (int i = 0; i < TAB_NAMES.length; i++) {
            boolean over = inside(mx, my, x, y, each - 2, TABS_H - 2);
            UiStyle.topTab(g, x, y, each - 2, TABS_H - 2, UiStyle.State.of(true, i == tab, over));
            boolean keys = each >= 64;
            String label = UiStyle.clip(font, each >= 70 ? TAB_NAMES[i] : TAB_SHORT[i], each - (keys ? 20 : 6));
            g.drawString(font, label, x + (each - 2 - font.width(label)) / 2 + (keys ? 5 : 0), y + 4, i == tab ? UiStyle.TEXT : UiStyle.TEXT_MUTED, false);
            if (keys) UiStyle.keyCap(g, font, KEYS[i], x + 2, y + 3);
            x += each;
        }
        return null;
    }

    private static final String[] KEYS = { "1", "2", "3", "4", "5" };

    @Nullable
    private String page(GuiGraphics g, int mx, int my) {
        int x = pageX(), y = pageY(), pw = pageW(), ph = pageH();
        scroll.fit(pageH, ph);
        g.enableScissor(x - 2, y, x + pw + 2, y + ph);
        String tip = null;
        int off = scroll.offset();
        boolean in = inside(mx, my, x, y, pw, ph);
        for (Block b : blocks) {
            int by = y + b.y - off;
            if (by + b.h < y || by > y + ph) continue;                     // out of view: not drawn
            b.draw(g, font, x, by, pw, mx, my);
            if (in && my >= by && my < by + b.h) {
                String t = b.tip(font, x, by, pw, mx, my);
                if (t != null) tip = t;
            }
        }
        g.disableScissor();
        UiStyle.scrollBar(g, x + pw + 2, y, ph, off, scroll.max(), ph);
        return tip;
    }

    @Nullable
    private String footer(GuiGraphics g, int mx, int my) {
        int y = top + h - FOOT_H + 3, bw = 64, bh = 18;
        int x = left + w - UiStyle.S2 - bw;
        UiStyle.button(g, font, "Close", x, y, bw, bh, UiStyle.State.of(true, false, inside(mx, my, x, y, bw, bh)));
        boolean near = nearEnough();
        String tip = null;
        x -= bw + UiStyle.S1;
        UiStyle.button(g, font, "Pack", x, y, bw, bh, UiStyle.State.of(near, false, inside(mx, my, x, y, bw, bh)));
        if (inside(mx, my, x, y, bw, bh)) tip = near ? "See what it carries (the pack screen)." : "Walk up to it to see its pack.";
        x -= bw + UiStyle.S1;
        UiStyle.button(g, font, "Talk", x, y, bw, bh, UiStyle.State.of(near, false, inside(mx, my, x, y, bw, bh)));
        if (inside(mx, my, x, y, bw, bh)) tip = near ? "Talk to it." : "Walk up to it to talk.";
        UiStyle.text(g, font, "Esc back · 1-5 tabs · wheel scrolls", left + UiStyle.S2, y + 5, x - left - 2 * UiStyle.S2, UiStyle.TEXT_FAINT);
        return tip;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return super.mouseClicked(mx, my, button);
        int y = top + HEADER_H + 2, x = left + UiStyle.S2;
        int each = Math.min(78, (w - 2 * UiStyle.S2) / TAB_NAMES.length);
        for (int i = 0; i < TAB_NAMES.length; i++, x += each) {
            if (inside(mx, my, x, y, each - 2, TABS_H - 2)) { pick(i); return true; }
        }
        int fy = top + h - FOOT_H + 3, bw = 64, fx = left + w - UiStyle.S2 - bw;
        if (inside(mx, my, fx, fy, bw, 18)) { UiStyle.click(); onClose(); return true; }
        fx -= bw + UiStyle.S1;
        if (inside(mx, my, fx, fy, bw, 18)) { if (nearEnough()) { UiStyle.click(); talk(TalkTopic.PACK); } return true; }
        fx -= bw + UiStyle.S1;
        if (inside(mx, my, fx, fy, bw, 18)) { if (nearEnough()) { UiStyle.click(); talk(TalkTopic.OPEN); } return true; }
        int px = pageX(), py = pageY();
        if (inside(mx, my, px, py, pageW(), pageH())) {
            int off = scroll.offset();
            for (Block b : blocks) {
                int by = py + b.y - off;
                if (my >= by && my < by + b.h && b.click(font, px, by, pageW(), mx, my, this)) return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    private void talk(TalkTopic topic) {
        PacketDistributor.sendToServer(new FolkTalkPayload(data.getInt("entity"), topic.ordinal(), ""));
    }

    private void pick(int i) {
        if (i == tab) return;
        UiStyle.click();
        tab = i;
        scroll.reset();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll.wheel(dy, 3 * UiStyle.LINE);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_5) { pick(key - GLFW.GLFW_KEY_1); return true; }
        if (key == GLFW.GLFW_KEY_TAB) { pick((tab + ((mods & GLFW.GLFW_MOD_SHIFT) != 0 ? TAB_NAMES.length - 1 : 1)) % TAB_NAMES.length); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_DOWN) { scroll.wheel(-1, pageH() - UiStyle.LINE); return true; }
        if (key == GLFW.GLFW_KEY_PAGE_UP) { scroll.wheel(1, pageH() - UiStyle.LINE); return true; }
        if (key == GLFW.GLFW_KEY_DOWN) { scroll.wheel(-1, UiStyle.LINE); return true; }
        if (key == GLFW.GLFW_KEY_UP) { scroll.wheel(1, UiStyle.LINE); return true; }
        return super.keyPressed(key, scan, mods);
    }

    private void openOther(UUID other) {
        UiStyle.click();
        open(other, TAB_IDS[tab], this);
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ------------------------------------------------------------------ laying the page out

    private void layout() {
        laidW = pageW();
        laidTab = tab;
        laidEpoch = TextCache.epoch();
        blocks.clear();
        Page p = new Page(font, laidW);
        switch (TAB_IDS[tab]) {
            case "about" -> about(p);
            case "life" -> life(p);
            case "work" -> work(p);
            case "day" -> day(p);
            default -> things(p);
        }
        // Everything else its card says that belongs on this tab.
        ListTag card = data.getCompound("card").getList(TAB_IDS[tab], Tag.TAG_COMPOUND);
        if (!card.isEmpty()) {
            p.heading(tab == 2 ? "At its work" : tab == 4 ? "Money and things" : "More");
            for (int i = 0; i < card.size(); i++) p.fact(card.getCompound(i).getString("k"), card.getCompound(i).getString("v"));
        }
        blocks.addAll(p.blocks);
        pageH = p.y;
    }

    private void about(Page p) {
        CompoundTag a = data.getCompound("about");
        p.heading("Who it is");
        p.fact("Kind", capital(a.getString("kind")));
        p.fact("Nature", a.getString("traits"));
        p.fact("Quirk", a.getString("quirk"));
        p.fact("Hobby", capital(a.getString("hobby")));
        String likes = join("; ", a.getString("loves").isEmpty() ? "" : "loves " + a.getString("loves"),
            a.getString("food").isEmpty() ? "" : "a favourite meal: " + a.getString("food"),
            a.getString("hates").isEmpty() ? "" : "can't stand " + a.getString("hates"));
        p.fact("Likes", capital(likes));
        ListTag goals = a.getList("goals", Tag.TAG_COMPOUND);
        p.heading("What it is working towards");
        if (goals.isEmpty()) p.note("Nothing in hand just now: it is content as it is.");
        for (int i = 0; i < goals.size(); i++) {
            CompoundTag gl = goals.getCompound(i);
            p.add(new Goal(font, capital(gl.getString("what")), gl.getInt("done"), gl.getString("words"), p.w));
        }
        if (!a.getString("story").isEmpty()) {
            p.heading("Its story");
            p.text(a.getString("story"), UiStyle.TEXT);
        }
    }

    private void life(Page p) {
        CompoundTag l = data.getCompound("life");
        p.heading("Family");
        List<Person> tree = new ArrayList<>();
        ListTag grand = l.getList("grandparents", Tag.TAG_COMPOUND);
        for (int i = 0; i < grand.size(); i++) tree.add(person(grand.getCompound(i), "Grandparent", l));
        if (!tree.isEmpty()) p.add(new People(font, "Grandparents", tree, p.w));
        tree = new ArrayList<>();
        ListTag parents = l.getList("parent_list", Tag.TAG_COMPOUND);
        for (int i = 0; i < parents.size(); i++) tree.add(person(parents.getCompound(i), "Parent", l));
        p.add(new People(font, "Parents", tree, p.w, "A founder of the town: its parents never lived here."));
        tree = new ArrayList<>();
        if (l.contains("partner")) tree.add(person(l.getCompound("partner"), "Partner", l));
        ListTag sibs = l.getList("siblings", Tag.TAG_COMPOUND);
        for (int i = 0; i < sibs.size(); i++) tree.add(person(sibs.getCompound(i), "Brother or sister", l));
        p.add(new People(font, "Partner, brothers and sisters", tree, p.w, "No partner, and no brothers or sisters in town."));
        tree = new ArrayList<>();
        ListTag kids = l.getList("children", Tag.TAG_COMPOUND);
        for (int i = 0; i < kids.size(); i++) tree.add(person(kids.getCompound(i), "Child", l));
        int more = l.getInt("children_all") - kids.size();
        p.add(new People(font, "Children" + (more > 0 ? " (and " + more + " not in town)" : ""), tree, p.w, "No children."));
        CompoundTag home = l.getCompound("home");
        p.heading("Home");
        if (home.getString("address").isEmpty()) {
            p.note(home.getString("lodging").isEmpty() ? "No home of its own yet: it is waiting for one."
                : "No house of its own yet: a spare bed at " + home.getString("lodging") + ".");
        } else {
            ListTag sign = home.getList("sign", Tag.TAG_STRING);
            String[] lines = new String[4];
            for (int i = 0; i < 4; i++) lines[i] = i < sign.size() ? sign.getString(i) : "";
            List<String> who = new ArrayList<>();
            ListTag members = home.getList("members", Tag.TAG_COMPOUND);
            for (int i = 0; i < members.size(); i++) who.add(members.getCompound(i).getString("name")
                + (members.getCompound(i).getBoolean("child") ? " (a child)" : ""));
            String terms = capital(home.getString("terms"))
                + (home.getInt("rent") > 0 ? "; rent " + home.getInt("rent") + "c a day" : "")
                + (home.getInt("saved") > 0 ? "; " + home.getInt("saved") + "c put by toward buying it" : "");
            p.add(new Sign(font, lines, capital(home.getString("address")), terms, String.join(", ", who), p.w));
        }
        p.heading("Friends and rivals");
        tree = new ArrayList<>();
        ListTag friends = l.getList("friends", Tag.TAG_COMPOUND);
        for (int i = 0; i < friends.size(); i++) tree.add(person(friends.getCompound(i), "Friend", l));
        p.add(new People(font, "Friends", tree, p.w, "No friends yet."));
        tree = new ArrayList<>();
        ListTag rivals = l.getList("rivals", Tag.TAG_COMPOUND);
        for (int i = 0; i < rivals.size(); i++) tree.add(person(rivals.getCompound(i), "Rival", l));
        p.add(new People(font, "Does not get on with", tree, p.w, "Nobody it can't abide."));
        if (l.contains("you")) {
            CompoundTag you = l.getCompound("you");
            p.heading("What it thinks of you");
            int aff = you.getInt("affinity");
            p.add(new Goal(font, capital(you.getString("standing")), (aff + 100) * 5, (aff > 0 ? "+" : "") + aff
                + (you.getBoolean("knows") ? "" : you.getString("heard_from").isEmpty() ? " · you have never spoken"
                : " · only heard of you, from " + you.getString("heard_from")), p.w));
        }
    }

    private Person person(CompoundTag c, String role, CompoundTag life) {
        int aff = c.getInt("affinity");
        String warmth = role.equals("Rival") ? "cold " + aff : aff >= life.getInt("close_at") ? "close, " + aff : aff > 0 ? "warm, " + aff : "";
        String sub = c.getBoolean("gone") ? "not in town" : c.getString("trade")
            + (c.contains("years") ? ", " + c.getInt("years") : "");
        String tip = c.getString("name") + " — " + role.toLowerCase(Locale.ROOT)
            + (c.getBoolean("gone") ? "; not in the town now" : "; " + c.getString("trade").toLowerCase(Locale.ROOT))
            + (warmth.isEmpty() ? "" : "\nFeeling: " + warmth + " (friends from " + life.getInt("friend_at") + ", close from " + life.getInt("close_at") + ")")
            + (c.hasUUID("uuid") && !c.getBoolean("gone") ? "\nClick for its profile." : "");
        return new Person(c.getString("name"), sub, c.hasUUID("uuid") && !c.getBoolean("gone") ? c.getUUID("uuid") : null,
            c.getBoolean("gone") ? UiStyle.EDGE_SOFT : Ui.job(c.getInt("ordinal")), tip,
            role.equals("Rival") ? UiStyle.DANGER_FILL : aff >= life.getInt("close_at") ? UiStyle.SUCCESS_FILL : 0);
    }

    private void work(Page p) {
        CompoundTag wk = data.getCompound("work");
        p.heading("Its trade");
        p.fact("Trade", wk.getString("trade") + (wk.getString("shift").isEmpty() ? "" : " · works " + wk.getString("shift")));
        int here = wk.getInt("xp_here"), next = wk.getInt("xp_next"), xp = wk.getInt("xp");
        if (next > here) p.add(new Goal(font, "Level " + wk.getInt("level"), (int) ((xp - here) * 1000L / (next - here)),
            (xp - here) + " of " + (next - here) + " experience to level " + (wk.getInt("level") + 1), p.w));
        p.fact("Other trades", wk.getString("trades"));
        p.fact("Knacks", wk.getString("knacks"));
        p.fact("Wage", wk.getInt("wage") > 0 ? wk.getInt("wage") + "c a day" + (wk.getString("wage_why").isEmpty() ? "" : " — " + wk.getString("wage_why")) : "");
        p.fact("Made", wk.getInt("made_yesterday") + "c worth yesterday; " + wk.getInt("earned") + "c earned in all");
        p.fact("Pace", wk.getString("pace"));
        p.heading("Now");
        p.fact("Doing", wk.getString("doing"));
        p.fact("Task in hand", wk.getString("task").isEmpty() ? "nothing queued" : wk.getString("task"));
        ListTag missing = wk.getList("missing", Tag.TAG_STRING);
        if (!missing.isEmpty()) {
            List<String> m = new ArrayList<>();
            for (int i = 0; i < missing.size(); i++) m.add(missing.getString(i));
            p.fact("Short of", String.join(", ", m), UiStyle.DANGER);
        }
        if (!wk.getString("tool").isEmpty()) p.add(new Items(font, "In hand", List.of(stack(wk.getString("tool"), 1)), List.of(""), p.w));
        ListTag record = wk.getList("record", Tag.TAG_STRING);
        p.heading("Its record");
        if (record.isEmpty()) p.note("Nothing to its name yet.");
        for (int i = 0; i < record.size(); i++) p.text("• " + record.getString(i), UiStyle.TEXT);
    }

    private void day(Page p) {
        CompoundTag d = data.getCompound("day");
        p.heading("Today");
        p.add(new Timeline(font, d, p.w));
        String now = d.getString("now_label"), plan = d.getString("plan_label");
        boolean matches = d.getString("now").equals(d.getString("plan"));
        p.fact("Now", now + " — " + d.getString("doing"));
        p.fact("By its plan", matches ? plan + ", as it is" : plan + " (it is " + now.toLowerCase(Locale.ROOT)
            + (d.getString("off_why").isEmpty() ? "" : ": " + d.getString("off_why")) + ")", matches ? UiStyle.TEXT : UiStyle.WARNING);
        p.fact("Bed", "about " + clock(d.getInt("bedtime")) + (d.getBoolean("bell") ? "; the town keeps the bell" : ""));
        ListTag meals = d.getList("meals", Tag.TAG_COMPOUND);
        for (int i = 0; i < meals.size(); i++) {
            CompoundTag m = meals.getCompound(i);
            String st = m.getString("state");
            p.fact(m.getString("label"), clock(m.getInt("from")) + "–" + clock(m.getInt("to")) + ": " + st,
                st.equals("missed") ? UiStyle.DANGER : st.equals("eaten") ? UiStyle.SUCCESS : UiStyle.TEXT);
        }
        ListTag mem = d.getList("memories", Tag.TAG_COMPOUND);
        p.heading("What it remembers");
        if (mem.isEmpty()) p.note("Nothing much has happened to it yet.");
        for (int i = 0; i < mem.size(); i++) {
            CompoundTag m = mem.getCompound(i);
            p.fact("Day " + m.getLong("day"), m.getString("text"), m.getInt("weight") >= 8 ? UiStyle.TEXT : UiStyle.TEXT_MUTED);
        }
    }

    private void things(Page p) {
        CompoundTag t = data.getCompound("things");
        p.heading("Money");
        if (t.contains("worth")) {
            int[] parts = { t.getInt("purse"), t.getInt("house_fund"), t.getInt("house_owned"), t.getInt("goods"),
                t.getInt("comforts_worth"), Math.max(0, t.getInt("bank")) };
            p.add(new Worth(font, parts, t.getInt("worth"), capital(t.getString("tier")), p.w));
            if (t.getInt("bank") < 0) p.fact("Owes", -t.getInt("bank") + "c on its house, at the bank", UiStyle.DANGER);
        } else {
            p.note("A child: its family keeps it. " + t.getInt("purse") + "c of its own.");
        }
        p.heading("What it wears");
        CompoundTag worn = t.getCompound("worn");
        List<ItemStack> wornStacks = new ArrayList<>();
        List<String> wornTips = new ArrayList<>();
        for (String slot : new String[]{ "head", "chest", "legs", "feet", "mainhand", "offhand" }) {
            if (!worn.contains(slot)) continue;
            CompoundTag c = worn.getCompound(slot);
            wornStacks.add(stack(c.getString("id"), c.getInt("n")));
            wornTips.add(c.getString("name") + " — " + SLOT_WORDS.getOrDefault(slot, slot) + " · worth " + c.getInt("worth") + "c");
        }
        if (wornStacks.isEmpty()) p.note("Its everyday clothes, nothing more.");
        else p.add(new Items(font, "", wornStacks, wornTips, p.w));
        p.heading("What it carries");
        ListTag items = t.getList("items", Tag.TAG_COMPOUND);
        List<ItemStack> stacks = new ArrayList<>();
        List<String> tips = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            CompoundTag c = items.getCompound(i);
            stacks.add(stack(c.getString("id"), c.getInt("n")));
            tips.add(c.getString("name") + " ×" + c.getInt("n") + " · worth " + c.getInt("worth") + "c"
                + (c.getBoolean("keepsake") ? "\nA keepsake: it will not part with it." : ""));
        }
        if (stacks.isEmpty()) p.note("Its pack is empty.");
        else p.add(new Items(font, "", stacks, tips, p.w));
        if (t.getInt("comforts") > 0) p.fact("Comforts at home", t.getInt("comforts") + " (worth " + t.getInt("comforts_worth") + "c)");
    }

    private static final java.util.Map<String, String> SLOT_WORDS = java.util.Map.of("head", "on its head", "chest", "on its back",
        "legs", "its legs", "feet", "its feet", "mainhand", "in its hand", "offhand", "in its other hand");

    private static ItemStack stack(String id, int n) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return ItemStack.EMPTY;
        return new ItemStack(BuiltInRegistries.ITEM.get(rl), Math.max(1, n));
    }

    // ------------------------------------------------------------------ the page's pieces

    /** A page being laid out: blocks one under the other, each at its y. */
    private static final class Page {
        final Font font;
        final int w;
        final List<Block> blocks = new ArrayList<>();
        int y;

        Page(Font font, int w) {
            this.font = font;
            this.w = w;
        }

        void add(Block b) {
            b.y = y;
            blocks.add(b);
            y += b.h + UiStyle.S1;
        }

        void heading(String text) {
            if (y > 0) y += UiStyle.S1;
            add(new Heading(text));
        }

        void fact(String key, String value) {
            fact(key, value, UiStyle.TEXT);
        }

        void fact(String key, String value, int colour) {
            if (value == null || value.isBlank()) return;
            int keyW = Math.min(KEY_W, w / 3);
            Fact f = new Fact(key, TextCache.splitPlain(font, value, w - keyW - UiStyle.S1), keyW, colour);
            f.y = y;
            blocks.add(f);
            y += f.h + 2;
        }

        void text(String value, int colour) {
            add(new Lines(TextCache.splitPlain(font, value, w), colour));
        }

        void note(String value) {
            add(new Lines(TextCache.splitPlain(font, value, w), UiStyle.TEXT_FAINT));
        }
    }

    /** A piece of the page, laid out once: where it sits (y, from the page's top) and how tall it is. */
    private abstract static class Block {
        int y, h;

        abstract void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my);

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) { return null; }

        boolean click(Font font, int x, int y, int w, double mx, double my, FolkProfileScreen screen) { return false; }
    }

    private static final class Heading extends Block {
        final String text;

        Heading(String text) { this.text = text; h = UiStyle.LINE; }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            UiStyle.heading(g, font, text, x, y, w);
        }
    }

    private static final class Lines extends Block {
        final List<FormattedCharSequence> lines;
        final int colour;

        Lines(List<FormattedCharSequence> lines, int colour) { this.lines = lines; this.colour = colour; h = Math.max(1, lines.size()) * UiStyle.LINE; }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x, y + i * UiStyle.LINE, colour, false);
        }
    }

    /** A label and its value, wrapped beside it. */
    private static final class Fact extends Block {
        final String key;
        final List<FormattedCharSequence> lines;
        final int keyW, colour;

        Fact(String key, List<FormattedCharSequence> lines, int keyW, int colour) {
            this.key = key;
            this.lines = lines;
            this.keyW = keyW;
            this.colour = colour;
            h = Math.max(1, lines.size()) * UiStyle.LINE;
        }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            g.drawString(font, UiStyle.clip(font, key, keyW - UiStyle.S1), x, y, UiStyle.TEXT_FAINT, false);
            for (int i = 0; i < lines.size(); i++) g.drawString(font, lines.get(i), x + keyW, y + i * UiStyle.LINE, colour, false);
        }

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) {
            return mx < x + keyW && font.width(key) > keyW - UiStyle.S1 ? key : null;
        }
    }

    /** A goal: what, a bar for how far on, and how far in words. */
    private static final class Goal extends Block {
        final String what, words, pct, tip;
        final int done;

        Goal(Font font, String what, int done, String words, int w) {
            this.what = what;
            this.words = words;
            this.done = Math.max(0, Math.min(1000, done));
            this.pct = this.done / 10 + "%";
            this.tip = what + "\n" + words + " (" + this.done / 10 + "% of the way)";
            h = 2 * UiStyle.LINE + 2;
        }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            UiStyle.text(g, font, what, x, y, w - 30, UiStyle.TEXT);
            g.drawString(font, pct, x + w - font.width(pct), y, UiStyle.TEXT_MUTED, false);
            int bw = Math.min(w, Math.max(80, w / 2));
            UiStyle.bar(g, x, y + UiStyle.LINE + 1, bw, 5, done / 1000F, done >= 1000 ? UiStyle.SUCCESS_FILL : UiStyle.ACCENT_FILL);
            UiStyle.text(g, font, words, x + bw + UiStyle.S2, y + UiStyle.LINE - 1, w - bw - UiStyle.S2, UiStyle.TEXT_FAINT);
        }

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) {
            return tip;
        }
    }

    private record Person(String name, String sub, @Nullable UUID id, int colour, String tip, int ring) {}

    /** A row of people, each a small card (a chip of its trade's colour, its name, a line under), flowing onto more rows. */
    private static final class People extends Block {
        static final int CARD_W = 104, CARD_H = 22;
        final String label;
        final List<Person> people;
        final String none;
        final int perRow;

        People(Font font, String label, List<Person> people, int w) {
            this(font, label, people, w, "");
        }

        People(Font font, String label, List<Person> people, int w, String none) {
            this.label = label;
            this.people = people;
            this.none = none;
            this.perRow = Math.max(1, (w + UiStyle.S1) / (CARD_W + UiStyle.S1));
            int rows = people.isEmpty() ? 0 : (people.size() + perRow - 1) / perRow;
            h = UiStyle.LINE + (people.isEmpty() ? (none.isEmpty() ? 0 : UiStyle.LINE) : rows * (CARD_H + 2));
        }

        int cardW(int w) { return (w - (perRow - 1) * UiStyle.S1) / perRow; }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            g.drawString(font, UiStyle.clip(font, label, w), x, y, UiStyle.TEXT_MUTED, false);
            if (people.isEmpty()) {
                if (!none.isEmpty()) g.drawString(font, UiStyle.clip(font, none, w), x, y + UiStyle.LINE, UiStyle.TEXT_FAINT, false);
                return;
            }
            int cw = cardW(w);
            for (int i = 0; i < people.size(); i++) {
                Person p = people.get(i);
                int cx = x + (i % perRow) * (cw + UiStyle.S1), cy = y + UiStyle.LINE + (i / perRow) * (CARD_H + 2);
                boolean over = p.id() != null && inside(mx, my, cx, cy, cw, CARD_H);
                UiStyle.tile(g, cx, cy, cw, CARD_H, over ? UiStyle.State.HOVER : UiStyle.State.NORMAL);
                Ui.chip(g, cx + 4, cy + 4, p.colour());
                if (p.ring() != 0) g.fill(cx + cw - 4, cy + 3, cx + cw - 2, cy + CARD_H - 3, p.ring());
                g.drawString(font, UiStyle.clip(font, p.name(), cw - 18), cx + 14, cy + 3, p.id() != null ? UiStyle.TEXT : UiStyle.TEXT_MUTED, false);
                g.drawString(font, UiStyle.clip(font, p.sub(), cw - 18), cx + 14, cy + 12, UiStyle.TEXT_FAINT, false);
            }
        }

        @Nullable
        private Person at(int x, int y, int w, double mx, double my) {
            int cw = cardW(w);
            for (int i = 0; i < people.size(); i++) {
                int cx = x + (i % perRow) * (cw + UiStyle.S1), cy = y + UiStyle.LINE + (i / perRow) * (CARD_H + 2);
                if (inside(mx, my, cx, cy, cw, CARD_H)) return people.get(i);
            }
            return null;
        }

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) {
            Person p = at(x, y, w, mx, my);
            return p == null ? null : p.tip();
        }

        boolean click(Font font, int x, int y, int w, double mx, double my, FolkProfileScreen screen) {
            Person p = at(x, y, w, mx, my);
            if (p == null || p.id() == null) return false;
            screen.openOther(p.id());
            return true;
        }
    }

    /** The sign by its door, as it hangs there, with its address and terms beside it. */
    private static final class Sign extends Block {
        static final int SIGN_W = 96, SIGN_H = 50;
        final String[] lines;
        final List<FormattedCharSequence> beside = new ArrayList<>();

        Sign(Font font, String[] lines, String address, String terms, String who, int w) {
            this.lines = lines;
            int tw = Math.max(60, w - SIGN_W - UiStyle.S3);
            beside.addAll(TextCache.splitPlain(font, address, tw));
            beside.addAll(TextCache.splitPlain(font, terms, tw));
            if (!who.isEmpty()) beside.addAll(TextCache.splitPlain(font, "Lives with: " + who, tw));
            h = Math.max(SIGN_H, beside.size() * UiStyle.LINE);
        }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            g.fill(x, y, x + SIGN_W, y + SIGN_H, 0xFF3B2A17);
            g.fill(x + 1, y + 1, x + SIGN_W - 1, y + SIGN_H - 1, 0xFF9C7A4C);
            g.fill(x + 1, y + 1, x + SIGN_W - 1, y + 2, 0xFFB89566);
            for (int i = 0; i < 4; i++) {
                String s = UiStyle.clip(font, lines[i], SIGN_W - 6);
                g.drawString(font, s, x + (SIGN_W - font.width(s)) / 2, y + 4 + i * 11, 0xFF20150A, false);
            }
            for (int i = 0; i < beside.size(); i++) {
                g.drawString(font, beside.get(i), x + SIGN_W + UiStyle.S3, y + i * UiStyle.LINE, i == 0 ? UiStyle.TEXT : UiStyle.TEXT_MUTED, false);
            }
        }

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) {
            return mx < x + SIGN_W ? "The sign by its door, as the town nailed it up." : null;
        }
    }

    /** A grid of item slots, with each one's words under the mouse. */
    private static final class Items extends Block {
        static final int SLOT = 18;
        final String label;
        final List<ItemStack> stacks;
        final List<String> tips;
        final int perRow;

        Items(Font font, String label, List<ItemStack> stacks, List<String> tips, int w) {
            this.label = label;
            this.stacks = stacks;
            this.tips = tips;
            this.perRow = Math.max(1, (w - (label.isEmpty() ? 0 : KEY_W)) / SLOT);
            h = ((stacks.size() + perRow - 1) / perRow) * SLOT;
        }

        int x0(int x) { return x + (label.isEmpty() ? 0 : KEY_W); }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            if (!label.isEmpty()) g.drawString(font, label, x, y + 5, UiStyle.TEXT_FAINT, false);
            int sx = x0(x);
            for (int i = 0; i < stacks.size(); i++) {
                int ix = sx + (i % perRow) * SLOT, iy = y + (i / perRow) * SLOT;
                UiStyle.well(g, ix, iy, SLOT, SLOT, 0xFF8B8B8B);
                g.renderItem(stacks.get(i), ix + 1, iy + 1);
                g.renderItemDecorations(font, stacks.get(i), ix + 1, iy + 1);
            }
        }

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) {
            int sx = x0(x);
            if (mx < sx) return null;
            int i = (my - y) / SLOT * perRow + (mx - sx) / SLOT;
            return (mx - sx) / SLOT < perRow && i >= 0 && i < tips.size() && !tips.get(i).isEmpty() ? tips.get(i) : null;
        }
    }

    /** Its net worth as one bar in parts (purse, put by, its house, things, comforts, the bank) with a key. */
    private static final class Worth extends Block {
        static final String[] NAMES = { "Purse", "Put by for a house", "Its house", "Things it carries", "Comforts", "At the bank" };
        static final int[] FILLS = { 0xFFD9B23A, 0xFF7FA35A, 0xFF4F7A3A, 0xFF6F8FB8, 0xFFB57BA6, 0xFF4A6FA0 };
        final int[] parts;
        final int total;
        final String head;
        final String[] keys = new String[6];

        Worth(Font font, int[] parts, int total, String tier, int w) {
            this.parts = parts;
            this.total = total;
            this.head = "Worth " + total + "c — " + tier;
            for (int i = 0; i < 6; i++) keys[i] = NAMES[i] + " " + parts[i] + "c";
            h = UiStyle.LINE + 10 + 2 * UiStyle.LINE;
        }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            g.drawString(font, head, x, y, UiStyle.TEXT, false);
            int by = y + UiStyle.LINE + 1, sum = 0;
            for (int p : parts) sum += p;
            g.fill(x, by, x + w, by + 7, UiStyle.EDGE_SOFT);
            int at = x;
            for (int i = 0; i < 6 && sum > 0; i++) {
                int pw = (int) ((long) parts[i] * w / sum);
                if (pw > 0) g.fill(at, by, at + pw, by + 7, FILLS[i]);
                at += pw;
            }
            int kx = x, ky = by + 10, kw = w / 3;
            for (int i = 0; i < 6; i++) {
                int cx = kx + (i % 3) * kw, cy = ky + (i / 3) * UiStyle.LINE;
                g.fill(cx, cy + 1, cx + 6, cy + 7, FILLS[i]);
                g.drawString(font, UiStyle.clip(font, keys[i], kw - 10), cx + 9, cy, UiStyle.TEXT_MUTED, false);
            }
        }

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) {
            return "Net worth: its purse, its share of what its household has put by toward a house and of a house it owns, "
                + "what it carries, the comforts of home, and its savings at the bank (less what it owes).";
        }
    }

    /** Today as a bar from six in the morning to six the next: its stretches, its meals, and now. */
    private static final class Timeline extends Block {
        record Stretch(String kind, String label, int from, int to, String tip) {}
        record Meal(String label, int from, int to, String state, String tip) {}
        final List<Stretch> stretches = new ArrayList<>();
        final List<Meal> meals = new ArrayList<>();

        Timeline(Font font, CompoundTag d, int w) {
            ListTag s = d.getList("slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < s.size(); i++) {
                CompoundTag c = s.getCompound(i);
                stretches.add(new Stretch(c.getString("kind"), c.getString("label"), c.getInt("from"), c.getInt("to"),
                    c.getString("label") + ", " + clock(c.getInt("from")) + "–" + clock(c.getInt("to"))));
            }
            ListTag m = d.getList("meals", Tag.TAG_COMPOUND);
            for (int i = 0; i < m.size(); i++) {
                CompoundTag c = m.getCompound(i);
                meals.add(new Meal(c.getString("label"), c.getInt("from"), c.getInt("to"), c.getString("state"),
                    c.getString("label") + ", " + clock(c.getInt("from")) + "–" + clock(c.getInt("to")) + ": " + c.getString("state")));
            }
            h = 14 + 10 + UiStyle.LINE;
        }

        static int colour(String kind) {
            return switch (kind) {
                case "SLEEP" -> C_SLEEP;
                case "WORK" -> C_WORK;
                case "BREAK" -> C_BREAK;
                case "SCHOOL" -> C_SCHOOL;
                case "WATCH" -> C_WATCH;
                case "LAID_UP" -> C_ILL;
                default -> C_FREE;
            };
        }

        void draw(GuiGraphics g, Font font, int x, int y, int w, int mx, int my) {
            int by = y + 2, bh = 12;
            UiStyle.well(g, x, by - 1, w, bh + 2);
            for (Stretch s : stretches) {
                int x0 = x + 1 + (int) ((long) s.from() * (w - 2) / 24000), x1 = x + 1 + (int) ((long) s.to() * (w - 2) / 24000);
                g.fill(x0, by, x1, by + bh, colour(s.kind()));
                g.fill(x0, by, x1, by + 1, Ui.lighten(colour(s.kind()), 0.3F));
                if (x1 - x0 > font.width(s.label()) + 4) g.drawString(font, s.label(), x0 + 2, by + 2, UiStyle.TEXT_ON_DARK, true);
            }
            for (Meal m : meals) {
                int mx0 = x + 1 + (int) ((long) m.from() * (w - 2) / 24000);
                int ink = m.state().equals("eaten") ? UiStyle.SUCCESS_FILL : m.state().equals("missed") ? UiStyle.DANGER_FILL : 0xFFF2F2F2;
                g.fill(mx0, by - 2, mx0 + 3, by + 2, ink);
                g.fill(mx0, by + bh - 2, mx0 + 3, by + bh + 2, ink);
            }
            // The hours along the foot: six, noon, six, midnight, six.
            for (int i = 0; i <= 4; i++) {
                int tx = x + (int) ((long) i * (w - 1) / 4);
                g.fill(tx, by + bh + 1, tx + 1, by + bh + 4, UiStyle.EDGE_SOFT);
                String l = HOURS[i];
                int lx = i == 0 ? tx : i == 4 ? tx - font.width(l) : tx - font.width(l) / 2;
                g.drawString(font, l, lx, by + bh + 5, UiStyle.TEXT_FAINT, false);
            }
            // Now, live from the world's clock.
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null) {
                long t = Math.floorMod(mc.level.getDayTime(), 24000L);
                int nx = x + 1 + (int) (t * (w - 2) / 24000);
                g.fill(nx - 1, by - 3, nx + 2, by + bh + 3, UiStyle.EDGE);
                g.fill(nx, by - 2, nx + 1, by + bh + 2, 0xFFFFFFFF);
            }
        }

        static final String[] HOURS = { "06:00", "12:00", "18:00", "00:00", "06:00" };

        @Nullable
        String tip(Font font, int x, int y, int w, int mx, int my) {
            long t = (long) (mx - x - 1) * 24000 / Math.max(1, w - 2);
            for (Meal m : meals) {
                int mx0 = x + 1 + (int) ((long) m.from() * (w - 2) / 24000);
                if (Math.abs(mx - mx0 - 1) <= 2) return m.tip();
            }
            for (Stretch s : stretches) {
                if (t >= s.from() && t < s.to()) return s.tip();
            }
            return null;
        }
    }

    // ------------------------------------------------------------------ words

    /** A day time (0 at six in the morning) as a clock reads it (FolkSchedule's own). */
    static String clock(long dayTime) {
        return com.jrpetty.mcassistant.entity.FolkSchedule.clock(dayTime);
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String join(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (!p.isEmpty()) sb.append(sb.length() == 0 ? "" : sep).append(p);
        return sb.toString();
    }
}
