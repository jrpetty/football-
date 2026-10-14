package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.AdminAskPayload;
import com.jrpetty.mcassistant.village.AdminSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * [admin] The admin panel: an operator's tools for a town, on one screen. Down the left, a search box over every
 * action, the areas (Town, Ages, People, Army &amp; War, Economy, Build, Events, World, Debug) and the player's recent
 * actions to run again; across the top, the town it acts on and a live status strip (the server's tick time, the
 * town's folk, the jobs running); in the middle, the area's actions, a row each with what it does; under them the
 * chosen action's values, Dry run and Run, and what came of it; on the right, the town's inspect report and the jobs
 * with their progress, live. A destructive action asks to confirm (and offers a snapshot first); a big one offers a
 * snapshot before it starts.
 *
 * <p>The panel only builds command lines: the server runs each exactly as the same command typed would, permission
 * and all (admin/AdminPanel). Nothing is allocated a frame: the lists, the wrapped lines and the status words are
 * made when the server's state comes in, the window is resized or the search changes.
 */
public class AdminScreen extends Screen {

    // ------------------------------------------------------------------ layout (GUI pixels, on UiStyle's grid)

    private static final int HEAD = 28, STATUS = 16, NAV = 116, SIDE = 176, DETAIL = 112, ROW_H = 26, MIN_W = 360;
    /** The right column shows only when the window is this wide. */
    private static final int SIDE_FROM = 560;
    /** Ticks between the panel's asks for fresh state while it is open (two seconds). */
    private static final int REFRESH_TICKS = 40;

    // ------------------------------------------------------------------ the server's state

    private static CompoundTag state = new CompoundTag();
    @Nullable private static AdminScreen current;
    private static long lastAsk;

    /** The server's state came in: open the panel, or bring the open one up to date. */
    public static void heard(CompoundTag t) {
        Minecraft mc = Minecraft.getInstance();
        if (t.getBoolean("open")) {
            state = t;
            AdminSpec.Area a = AdminSpec.Area.byKey(t.getString("area"));
            mc.setScreen(new AdminScreen(a));
            return;
        }
        for (String k : t.getAllKeys()) state.put(k, t.get(k).copy());
        if (!t.contains("result")) {
            state.remove("result");
            state.remove("confirm");
        }
        if (current != null) current.stateChanged(t.contains("result"));
    }

    /** While the panel is open: a fresh state every two seconds. */
    static void tickRefresh() {
        Minecraft mc = Minecraft.getInstance();
        if (current == null || mc.screen != current || mc.level == null) return;
        long now = mc.level.getGameTime();
        if (now - lastAsk < REFRESH_TICKS) return;
        lastAsk = now;
        PacketDistributor.sendToServer(new AdminAskPayload("refresh", state.getString("town"), ""));
    }

    private static void send(String line) {
        PacketDistributor.sendToServer(new AdminAskPayload("run", state.getString("town"), line));
    }

    // ------------------------------------------------------------------ the screen's own state

    private int left, top, w, h, navW, sideW;
    private AdminSpec.Area area;
    private final List<AdminSpec.Action> shown = new ArrayList<>();
    @Nullable private AdminSpec.Action chosen;
    private EditBox search;
    private final List<EditBox> boxes = new ArrayList<>();
    /** The chosen action's values: one per parameter, then the folk field. */
    private final List<String> values = new ArrayList<>();
    private final UiStyle.Scroll listScroll = new UiStyle.Scroll(), sideScroll = new UiStyle.Scroll();
    private boolean townsOpen;
    /** A confirm on screen: "server" (a token to give), "big" (a snapshot offered), or null. */
    @Nullable private String modal;
    private String pendingLine = "";
    // made when the state or the size changes, read every frame
    private final List<FormattedCharSequence> inspect = new ArrayList<>(), result = new ArrayList<>();
    private boolean resultOk = true;
    private String statusTick = "", statusTown = "", statusJobs = "";
    private int tickFill = UiStyle.SUCCESS_FILL;
    private final List<String> jobs = new ArrayList<>(), jobIds = new ArrayList<>();
    private final List<float[]> jobFrac = new ArrayList<>();
    private final List<String> recent = new ArrayList<>(), towns = new ArrayList<>();
    private final List<String> allowed = new ArrayList<>();
    private String tip = "";
    private int tipX, tipY;

    public AdminScreen(@Nullable AdminSpec.Area area) {
        super(Component.literal("Admin"));
        this.area = area == null ? AdminSpec.Area.TOWN : area;
    }

    @Override
    protected void init() {
        current = this;
        w = Math.max(MIN_W, Math.min(width - UiStyle.S4, 640));
        h = Math.max(240, Math.min(height - UiStyle.S4, 380));
        left = (width - w) / 2;
        top = (height - h) / 2;
        navW = NAV;
        sideW = w >= SIDE_FROM ? SIDE : 0;
        String typed = search == null ? "" : search.getValue();
        search = new EditBox(font, left + UiStyle.S2, top + HEAD + STATUS + UiStyle.S2, navW - UiStyle.S3, 16, Component.literal("Search"));
        search.setHint(Component.literal("Search actions"));
        search.setValue(typed);
        search.setResponder(s -> refilter());
        stateChanged(state.contains("result"));
        refilter();
    }

    @Override
    public void removed() {
        if (current == this) current = null;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------ geometry

    private int listX() { return left + navW + UiStyle.S2; }
    private int listY() { return top + HEAD + STATUS + UiStyle.S2; }
    private int listW() { return w - navW - sideW - 2 * UiStyle.S2 - (sideW > 0 ? UiStyle.S2 : 0); }
    private int listH() { return h - HEAD - STATUS - DETAIL - 2 * UiStyle.S2; }
    private int detailY() { return listY() + listH() + UiStyle.S1; }
    private int sideX() { return left + w - sideW - UiStyle.S2; }

    // ------------------------------------------------------------------ made on change

    /** The actions the list shows: the search's matches (any area) or the area's. */
    private void refilter() {
        shown.clear();
        String q = search == null ? "" : search.getValue().trim();
        shown.addAll(q.isEmpty() ? AdminSpec.of(area) : AdminSpec.search(q));
        if (chosen != null && !shown.contains(chosen)) choose(null);
        listScroll.reset();
    }

    /** The server's state, turned into what the frames draw. */
    private void stateChanged(boolean newResult) {
        int textW = Math.max(60, sideW - UiStyle.S3);
        inspect.clear();
        for (Tag t : state.getList("inspect", Tag.TAG_STRING)) inspect.addAll(TextCache.splitPlain(font, t.getAsString(), textW));
        if (newResult || state.contains("result")) {
            result.clear();
            resultOk = state.getBoolean("resultOk");
            for (Tag t : state.getList("result", Tag.TAG_STRING)) result.addAll(TextCache.splitPlain(font, t.getAsString(), Math.max(60, listW() - UiStyle.S2)));
            if (newResult && state.contains("confirm")) modal = "server";
        }
        float mspt = state.getFloat("mspt");
        statusTick = String.format(Locale.ROOT, "Tick %.1f ms", mspt);
        tickFill = mspt < 40F ? UiStyle.SUCCESS_FILL : mspt < 50F ? UiStyle.WARNING_FILL : UiStyle.DANGER_FILL;
        statusTown = state.getString("town").isEmpty() ? "No town" : state.getString("town") + " · " + state.getInt("folk") + " folk";
        jobs.clear();
        jobIds.clear();
        jobFrac.clear();
        for (Tag t : state.getList("jobs", Tag.TAG_COMPOUND)) {
            CompoundTag j = (CompoundTag) t;
            jobs.add(j.getString("label") + " " + j.getInt("done") + "/" + j.getInt("total"));
            jobIds.add(j.getString("id"));
            jobFrac.add(new float[]{ j.getInt("done") / (float) Math.max(1, j.getInt("total")) });
        }
        statusJobs = jobs.isEmpty() ? "No jobs running" : jobs.size() + (jobs.size() == 1 ? " job running" : " jobs running");
        recent.clear();
        for (Tag t : state.getList("recent", Tag.TAG_STRING)) recent.add(t.getAsString().replace("/village admin ", ""));
        towns.clear();
        for (Tag t : state.getList("towns", Tag.TAG_STRING)) towns.add(t.getAsString());
        allowed.clear();
        for (Tag t : state.getList("areas", Tag.TAG_STRING)) allowed.add(t.getAsString());
    }

    /** Choose an action: its value fields laid out under the list. */
    private void choose(@Nullable AdminSpec.Action a) {
        for (EditBox b : boxes) removeWidget(b);
        boxes.clear();
        values.clear();
        chosen = a;
        if (a == null) return;
        for (AdminSpec.Param p : a.params()) values.add(p.fallback());
        if (a.scope() == AdminSpec.Scope.FOLK) values.add("@all");
        int x = listX() + UiStyle.S1, y = detailY() + 26;
        for (int i = 0; i < values.size(); i++) {
            AdminSpec.Param p = i < a.params().size() ? a.params().get(i) : null;
            int fw = fieldW(p);
            if (p == null || typed(p)) {
                final int at = i;
                EditBox b = new EditBox(font, x, y, fw, 16, Component.literal(p == null ? "folk" : p.name()));
                b.setHint(Component.literal(p == null ? "folk: name, @trade, @all" : p.name()));
                b.setValue(values.get(i));
                b.setMaxLength(64);
                b.setResponder(s -> values.set(at, s));
                boxes.add(b);
                addRenderableWidget(b);
            }
            x += fw + UiStyle.S1;
        }
    }

    /** A parameter typed into a box (a number, an item, a name); the rest are picked from their choices. */
    private static boolean typed(AdminSpec.Param p) {
        return switch (p.kind()) {
            case INT, ITEM, FILE, TEXT -> true;
            case WORD -> p.choices().isEmpty();
            default -> false;
        };
    }

    private int fieldW(@Nullable AdminSpec.Param p) {
        if (p == null) return 92;
        return switch (p.kind()) {
            case INT -> 52;
            case ITEM, FILE, TEXT -> 96;
            default -> 84;
        };
    }

    /** A picked parameter's choices. */
    private List<String> choices(AdminSpec.Param p) {
        return switch (p.kind()) {
            case WORD -> p.choices();
            case AGE -> AdminSpec.AGES;
            case TIER -> AdminSpec.TIERS;
            case PHASE -> AdminSpec.PHASES;
            case SCENARIO -> AdminSpec.SCENARIOS;
            case EVENT -> AdminSpec.EVENTS;
            case TRADE -> list("trades");
            case CIVIC -> list("civics");
            case STRUCTURE -> list("structures");
            case PLAYER -> list("players");
            case TOWN -> {
                List<String> t = new ArrayList<>(towns);
                t.add(0, "rival");
                yield t;
            }
            default -> List.of();
        };
    }

    private static List<String> list(String key) {
        List<String> out = new ArrayList<>();
        for (Tag t : state.getList(key, Tag.TAG_STRING)) out.add(t.getAsString());
        return out;
    }

    /** The chosen action as a command line, with the town and any flag. */
    private String line(@Nullable String flag) {
        if (chosen == null) return "";
        StringBuilder sb = new StringBuilder(chosen.path());
        for (int i = 0; i < chosen.params().size(); i++) {
            String v = values.get(i).trim();
            if (v.isEmpty()) continue;
            sb.append(' ').append(v.contains(" ") ? "\"" + v + "\"" : v);
        }
        if (chosen.scope() != AdminSpec.Scope.WORLD && !state.getString("town").isEmpty()) {
            String t = state.getString("town");
            sb.append(" town:").append(t.contains(" ") ? "\"" + t + "\"" : t);
        }
        if (chosen.scope() == AdminSpec.Scope.FOLK) {
            String f = values.get(values.size() - 1).trim();
            if (!f.isEmpty()) sb.append(" folk:").append(f.contains(" ") ? "\"" + f + "\"" : f);
        }
        if (flag != null) sb.append(' ').append(flag);
        return sb.toString();
    }

    private boolean mayUse(AdminSpec.Area a) {
        return allowed.contains(a.key());
    }

    // ------------------------------------------------------------------ drawing

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderTransparentBackground(g);
        tip = "";
        boolean modalUp = modal != null;
        int hx = modalUp ? -1 : mx, hy = modalUp ? -1 : my;
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        header(g, hx, hy);
        status(g);
        nav(g, hx, hy);
        list(g, hx, hy);
        detail(g, hx, hy);
        if (sideW > 0) side(g, hx, hy);
        for (EditBox b : boxes) b.render(g, mx, my, pt);
        search.render(g, mx, my, pt);
        if (townsOpen) townList(g, mx, my);
        if (modalUp) modal(g, mx, my);
        if (!tip.isEmpty()) UiStyle.tooltip(g, font, tip, tipX, tipY, 220);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // drawn in render, once
    }

    private boolean in(double mx, double my, int x, int y, int bw, int bh) {
        return mx >= x && mx < x + bw && my >= y && my < y + bh;
    }

    private void tip(String text, int mx, int my) {
        tip = text;
        tipX = mx;
        tipY = my;
    }

    private int closeX() { return left + w - UiStyle.S2 - font.width("Close") - 10; }
    private int townX() { return left + UiStyle.S2 + UiStyle.titleWidth(font, "Admin") + UiStyle.S3; }
    private static final int TOWN_W = 150;

    private void header(GuiGraphics g, int mx, int my) {
        UiStyle.headerBand(g, left, top, w, HEAD);
        UiStyle.title(g, font, "Admin", left + UiStyle.S2, top + 10);
        String town = state.getString("town").isEmpty() ? "No town" : state.getString("town");
        int tx = townX();
        boolean over = in(mx, my, tx, top + 5, TOWN_W, 18);
        UiStyle.button(g, font, "Town: " + town + " ▾", tx, top + 5, TOWN_W, 18, UiStyle.State.of(!towns.isEmpty(), townsOpen, over));
        if (over) tip("The town the actions act on. Click to choose another.", mx, my);
        int cx = closeX(), cw = font.width("Close") + 10;
        UiStyle.button(g, font, "Close", cx, top + 5, cw, 18, UiStyle.State.of(true, false, in(mx, my, cx, top + 5, cw, 18)));
    }

    private void status(GuiGraphics g) {
        int y = top + HEAD + 2;
        g.fill(left + 1, y, left + w - 1, y + STATUS - 2, UiStyle.PAGE);
        int x = left + UiStyle.S2;
        x += UiStyle.badge(g, font, statusTick, x, y + 1, tickFill) + UiStyle.S2;
        g.drawString(font, statusTown, x, y + 3, UiStyle.TEXT_MUTED, false);
        x += font.width(statusTown) + UiStyle.S3;
        g.drawString(font, statusJobs, x, y + 3, jobs.isEmpty() ? UiStyle.TEXT_FAINT : UiStyle.INFO, false);
        x += font.width(statusJobs) + UiStyle.S2;
        if (!jobFrac.isEmpty()) UiStyle.bar(g, x, y + 5, Math.max(20, Math.min(120, left + w - UiStyle.S2 - x)), 4, jobFrac.get(0)[0], UiStyle.ACCENT_FILL);
    }

    private void nav(GuiGraphics g, int mx, int my) {
        int x = left + 1, y = top + HEAD + STATUS + UiStyle.S2 + 20;
        boolean searching = !search.getValue().isBlank();
        for (AdminSpec.Area a : AdminSpec.Area.values()) {
            boolean ok = mayUse(a), over = in(mx, my, x, y, navW - UiStyle.S2, UiStyle.TAB);
            UiStyle.sideTab(g, x, y, navW - UiStyle.S2, UiStyle.TAB, UiStyle.State.of(ok, a == area && !searching, over));
            g.drawString(font, UiStyle.clip(font, a.title, navW - UiStyle.S4), x + UiStyle.S2, y + 7, ok ? (a == area ? UiStyle.ACCENT : UiStyle.TEXT) : UiStyle.TEXT_DISABLED, false);
            if (over) tip(ok ? a.blurb : a.title + ": not yours (the server's permissions)", mx, my);
            y += UiStyle.TAB;
        }
        y += UiStyle.S2;
        UiStyle.heading(g, font, "Recent", left + UiStyle.S2, y, navW - UiStyle.S3);
        y += 14;
        if (recent.isEmpty()) g.drawString(font, "Nothing yet", left + UiStyle.S2, y, UiStyle.TEXT_FAINT, false);
        int bottom = top + h - UiStyle.S2;
        for (int i = 0; i < recent.size() && y + UiStyle.LINE <= bottom; i++) {
            String r = recent.get(i);
            boolean over = in(mx, my, left + UiStyle.S1, y - 1, navW - UiStyle.S2, UiStyle.LINE + 1);
            if (over) g.fill(left + UiStyle.S1, y - 1, left + navW - UiStyle.S1, y + UiStyle.LINE, UiStyle.TILE_HOVER);
            boolean clipped = UiStyle.text(g, font, "↻ " + r, left + UiStyle.S2, y, navW - UiStyle.S3, over ? UiStyle.ACCENT : UiStyle.TEXT_MUTED);
            if (over) tip("Run again: " + r + (clipped ? "" : ""), mx, my);
            y += UiStyle.LINE + 2;
        }
    }

    private void list(GuiGraphics g, int mx, int my) {
        int x = listX(), y = listY(), lw = listW(), lh = listH();
        UiStyle.well(g, x, y, lw, lh, UiStyle.PAGE);
        if (shown.isEmpty()) {
            UiStyle.empty(g, font, "No actions match", "Try fewer words, or clear the search.", x, y, lw, lh);
            return;
        }
        listScroll.fit(shown.size() * ROW_H, lh - 2);
        g.enableScissor(x + 1, y + 1, x + lw - 1, y + lh - 1);
        int ry = y + 1 - listScroll.offset();
        for (AdminSpec.Action a : shown) {
            if (ry + ROW_H > y && ry < y + lh) {
                boolean ok = mayUse(a.area()), over = ok && in(mx, my, x + 1, Math.max(ry, y), lw - 6, ROW_H - 1) && my < y + lh;
                UiStyle.tile(g, x + 2, ry + 1, lw - 8, ROW_H - 2, UiStyle.State.of(ok, a == chosen, over));
                String title = a.path() + (a.destructive() ? "  ⚠" : a.big() ? "  ⏱" : "");
                g.drawString(font, UiStyle.clip(font, title, lw - 20), x + UiStyle.S2, ry + 4, ok ? (a.destructive() ? UiStyle.DANGER : UiStyle.TEXT) : UiStyle.TEXT_DISABLED, false);
                boolean cut = UiStyle.text(g, font, a.summary(), x + UiStyle.S2, ry + 14, lw - 20, ok ? UiStyle.TEXT_FAINT : UiStyle.TEXT_DISABLED);
                if (over && (cut || a.destructive() || a.big())) {
                    tip(a.summary() + (a.destructive() ? " Destructive: it asks you to confirm." : a.big() ? " Runs as a job over a few seconds." : ""), mx, my);
                }
                if (!ok && in(mx, my, x + 1, ry, lw - 6, ROW_H)) tip("Not yours: the " + a.area().title + " tools need the server's permission.", mx, my);
            }
            ry += ROW_H;
        }
        g.disableScissor();
        UiStyle.scrollBar(g, x + lw - 5, y + 1, lh - 2, listScroll.offset(), listScroll.max(), lh - 2);
    }

    private int runX() { return listX() + listW() - 54; }
    private int dryX() { return runX() - 64; }

    private void detail(GuiGraphics g, int mx, int my) {
        int x = listX(), y = detailY(), dw = listW();
        UiStyle.card(g, x, y, dw, DETAIL - UiStyle.S1, chosen == null ? UiStyle.EDGE_SOFT : chosen.destructive() ? UiStyle.DANGER_FILL : UiStyle.ACCENT_FILL);
        if (chosen == null) {
            UiStyle.empty(g, font, "Choose an action", "Its values, Dry run and Run appear here.", x, y, dw, 40);
        } else {
            g.drawString(font, UiStyle.clip(font, chosen.usage().replace("/village admin ", ""), dw - UiStyle.S3), x + UiStyle.S2, y + 4, UiStyle.TEXT, false);
            UiStyle.text(g, font, "e.g. " + chosen.example(), x + UiStyle.S2, y + 14, dw - UiStyle.S3, UiStyle.TEXT_FAINT);
            int fx = x + UiStyle.S1, fy = y + 26;
            for (int i = 0; i < values.size(); i++) {
                AdminSpec.Param p = i < chosen.params().size() ? chosen.params().get(i) : null;
                int fw = fieldW(p);
                if (p != null && !typed(p)) {
                    boolean over = in(mx, my, fx, fy, fw, 16);
                    UiStyle.button(g, font, values.get(i).isEmpty() ? "(" + p.name() + ")" : values.get(i) + " ▸", fx, fy, fw, 16, UiStyle.State.of(true, false, over));
                    if (over) tip(p.name() + ": click for the next choice, right-click for the one before" + (p.optional() ? " (optional)" : ""), mx, my);
                }
                fx += fw + UiStyle.S1;
            }
            int by = fy + 22;
            boolean big = chosen.big() || chosen.destructive();
            boolean overDry = in(mx, my, dryX(), by, 60, 18), overRun = in(mx, my, runX(), by, 50, 18);
            UiStyle.button(g, font, "Dry run", dryX(), by, 60, 18, UiStyle.State.of(true, false, overDry));
            UiStyle.tile(g, runX(), by, 50, 18, UiStyle.State.of(true, false, overRun));
            g.fill(runX() + 1, by + 1, runX() + 49, by + 17, chosen.destructive() ? UiStyle.DANGER_FILL : UiStyle.ACCENT_FILL);
            String run = big ? "Run…" : "Run";
            g.drawString(font, run, runX() + (50 - font.width(run)) / 2, by + 5, UiStyle.TEXT_ON_DARK, true);
            if (overDry) tip("Show what it would change, changing nothing.", mx, my);
            if (overRun) tip(chosen.destructive() ? "Asks you to confirm first." : chosen.big() ? "Offers a snapshot first." : "Run it now. (Enter)", mx, my);
            UiStyle.keyCap(g, font, "Enter", runX() - 92, by + 4);
        }
        int ry = y + DETAIL - UiStyle.S1 - 2 - Math.min(3, result.size()) * UiStyle.LINE;
        for (int i = 0; i < Math.min(3, result.size()); i++) {
            g.drawString(font, result.get(i), x + UiStyle.S2, ry, resultOk ? UiStyle.SUCCESS : UiStyle.DANGER, false);
            ry += UiStyle.LINE;
        }
    }

    private void side(GuiGraphics g, int mx, int my) {
        int x = sideX(), y = listY(), sh = h - HEAD - STATUS - 2 * UiStyle.S2;
        UiStyle.well(g, x, y, sideW, sh, UiStyle.PAGE);
        int jy = y + sh - UiStyle.S1 - Math.max(1, jobs.size()) * 24 - 14;
        UiStyle.heading(g, font, "Town", x + UiStyle.S1, y + UiStyle.S1, sideW - UiStyle.S2);
        int ty = y + 16, room = jy - ty - UiStyle.S1;
        if (inspect.isEmpty()) g.drawString(font, "No report (no town, or not yours)", x + UiStyle.S1, ty, UiStyle.TEXT_FAINT, false);
        sideScroll.fit(inspect.size() * UiStyle.LINE, room);
        g.enableScissor(x + 1, ty, x + sideW - 1, ty + room);
        int ly = ty - sideScroll.offset();
        for (FormattedCharSequence l : inspect) {
            if (ly + UiStyle.LINE > ty && ly < ty + room) g.drawString(font, l, x + UiStyle.S1, ly, UiStyle.TEXT_MUTED, false);
            ly += UiStyle.LINE;
        }
        g.disableScissor();
        UiStyle.scrollBar(g, x + sideW - 5, ty, room, sideScroll.offset(), sideScroll.max(), room);
        UiStyle.heading(g, font, "Jobs", x + UiStyle.S1, jy, sideW - UiStyle.S2);
        jy += 14;
        if (jobs.isEmpty()) g.drawString(font, "None running", x + UiStyle.S1, jy, UiStyle.TEXT_FAINT, false);
        for (int i = 0; i < jobs.size(); i++) {
            UiStyle.text(g, font, jobs.get(i), x + UiStyle.S1, jy, sideW - 30, UiStyle.TEXT_MUTED);
            UiStyle.bar(g, x + UiStyle.S1, jy + 11, sideW - 30, 4, jobFrac.get(i)[0], UiStyle.ACCENT_FILL);
            int cx = x + sideW - 20;
            boolean over = in(mx, my, cx, jy, 14, 14);
            UiStyle.button(g, font, "×", cx, jy, 14, 14, UiStyle.State.of(true, false, over));
            if (over) tip("Cancel this job (what it did stays done)", mx, my);
            jy += 24;
        }
    }

    private void townList(GuiGraphics g, int mx, int my) {
        int x = townX(), y = top + 24, n = Math.min(10, towns.size());
        g.fill(x - 1, y - 1, x + TOWN_W + 1, y + n * 14 + 1, UiStyle.EDGE);
        for (int i = 0; i < n; i++) {
            boolean over = in(mx, my, x, y + i * 14, TOWN_W, 14);
            g.fill(x, y + i * 14, x + TOWN_W, y + i * 14 + 14, over ? UiStyle.TILE_HOVER : UiStyle.TILE);
            UiStyle.text(g, font, towns.get(i), x + UiStyle.S1, y + i * 14 + 3, TOWN_W - UiStyle.S2,
                towns.get(i).equals(state.getString("town")) ? UiStyle.ACCENT : UiStyle.TEXT);
        }
    }

    private static final int MODAL_W = 280, MODAL_H = 120;

    private void modal(GuiGraphics g, int mx, int my) {
        g.fill(left, top, left + w, top + h, 0x80000000);
        int x = (width - MODAL_W) / 2, y = (height - MODAL_H) / 2;
        UiStyle.panel(g, x, y, MODAL_W, MODAL_H);
        boolean server = "server".equals(modal);
        UiStyle.title(g, font, server ? "Confirm a destructive action" : "A big change", x + UiStyle.S2, y + UiStyle.S2);
        int ty = y + 22;
        List<String> say = new ArrayList<>();
        if (server) {
            for (Tag t : state.getList("result", Tag.TAG_STRING)) if (t.getAsString().startsWith("WILL ")) say.add(t.getAsString().substring(5));
            say.add("It cannot be undone. Back up first: /save-all,");
            say.add("and a snapshot of the town's saved data.");
        } else {
            say.add(chosen == null ? "" : chosen.summary());
            say.add("It runs over a few seconds. /save-all first is wise;");
            say.add("a snapshot keeps the town's saved data (not blocks).");
        }
        for (String s : say) {
            UiStyle.text(g, font, s, x + UiStyle.S2, ty, MODAL_W - UiStyle.S4, UiStyle.TEXT_MUTED);
            ty += UiStyle.LINE;
        }
        int by = y + MODAL_H - 26;
        String[] labels = server ? new String[]{ "Cancel", "Snapshot first", "Confirm" } : new String[]{ "Cancel", "With snapshot", "Run" };
        int bx = x + MODAL_W - UiStyle.S2;
        for (int i = labels.length - 1; i >= 0; i--) {
            int bw = font.width(labels[i]) + 14;
            bx -= bw;
            UiStyle.button(g, font, labels[i], bx, by, bw, 18, UiStyle.State.of(true, i == labels.length - 1, in(mx, my, bx, by, bw, 18)));
            bx -= UiStyle.S1;
        }
    }

    /** Which of the modal's buttons is under the mouse: 0 cancel, 1 snapshot, 2 go; -1 none. */
    private int modalButton(double mx, double my) {
        int x = (width - MODAL_W) / 2, y = (height - MODAL_H) / 2, by = y + MODAL_H - 26;
        boolean server = "server".equals(modal);
        String[] labels = server ? new String[]{ "Cancel", "Snapshot first", "Confirm" } : new String[]{ "Cancel", "With snapshot", "Run" };
        int bx = x + MODAL_W - UiStyle.S2;
        for (int i = labels.length - 1; i >= 0; i--) {
            int bw = font.width(labels[i]) + 14;
            bx -= bw;
            if (in(mx, my, bx, by, bw, 18)) return i;
            bx -= UiStyle.S1;
        }
        return -1;
    }

    // ------------------------------------------------------------------ input

    private void run(boolean dry) {
        if (chosen == null) return;
        UiStyle.click();
        if (dry) {
            send(line(AdminSpec.DRY_RUN));
        } else if (chosen.big() && !chosen.destructive()) {
            pendingLine = line(null);
            modal = "big";
        } else {
            send(line(null));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (modal != null) {
            int b = modalButton(mx, my);
            if (b < 0) return true;
            UiStyle.click();
            String town = state.getString("town");
            if ("server".equals(modal)) {
                if (b == 1) { send("snapshot town:\"" + town + "\""); return true; }
                if (b == 2) send("confirm " + state.getString("confirm"));
            } else {
                if (b == 1) send(pendingLine + " " + AdminSpec.SNAPSHOT);
                if (b == 2) send(pendingLine);
            }
            modal = null;
            return true;
        }
        if (townsOpen) {
            int x = townX(), y = top + 24, n = Math.min(10, towns.size());
            townsOpen = false;
            if (in(mx, my, x, y, TOWN_W, n * 14)) {
                UiStyle.click();
                state.putString("town", towns.get((int) ((my - y) / 14)));
                lastAsk = 0;
                tickRefresh();
                return true;
            }
        }
        if (in(mx, my, closeX(), top + 5, font.width("Close") + 10, 18)) { UiStyle.click(); onClose(); return true; }
        if (in(mx, my, townX(), top + 5, TOWN_W, 18) && !towns.isEmpty()) { UiStyle.click(); townsOpen = true; return true; }
        // the areas
        int ny = top + HEAD + STATUS + UiStyle.S2 + 20;
        for (AdminSpec.Area a : AdminSpec.Area.values()) {
            if (in(mx, my, left, ny, navW - UiStyle.S2, UiStyle.TAB) && mayUse(a)) {
                UiStyle.click();
                area = a;
                search.setValue("");
                refilter();
                return true;
            }
            ny += UiStyle.TAB;
        }
        // recent: run again
        int ry = ny + UiStyle.S2 + 14;
        for (String r : recent) {
            if (in(mx, my, left + UiStyle.S1, ry - 1, navW - UiStyle.S2, UiStyle.LINE + 1)) { UiStyle.click(); send(r); return true; }
            ry += UiStyle.LINE + 2;
        }
        // the list
        int x = listX(), y = listY();
        if (in(mx, my, x, y, listW() - 6, listH())) {
            int i = (int) ((my - y - 1 + listScroll.offset()) / ROW_H);
            if (i >= 0 && i < shown.size() && mayUse(shown.get(i).area())) {
                UiStyle.click();
                choose(shown.get(i));
                return true;
            }
        }
        // the detail: picked values, Dry run, Run
        if (chosen != null) {
            int fx = listX() + UiStyle.S1, fy = detailY() + 26;
            for (int i = 0; i < values.size(); i++) {
                AdminSpec.Param p = i < chosen.params().size() ? chosen.params().get(i) : null;
                int fw = fieldW(p);
                if (p != null && !typed(p) && in(mx, my, fx, fy, fw, 16)) {
                    List<String> c = choices(p);
                    if (!c.isEmpty()) {
                        int at = c.indexOf(values.get(i));
                        values.set(i, c.get(Math.floorMod(at + (button == 1 ? -1 : 1), c.size())));
                        UiStyle.click();
                    }
                    return true;
                }
                fx += fw + UiStyle.S1;
            }
            int by = fy + 22;
            if (in(mx, my, dryX(), by, 60, 18)) { run(true); return true; }
            if (in(mx, my, runX(), by, 50, 18)) { run(false); return true; }
        }
        // a job's cancel
        if (sideW > 0) {
            int sx = sideX(), sh = h - HEAD - STATUS - 2 * UiStyle.S2;
            int jy = listY() + sh - UiStyle.S1 - Math.max(1, jobs.size()) * 24 - 14 + 14;
            for (String id : jobIds) {
                if (in(mx, my, sx + sideW - 20, jy, 14, 14)) { UiStyle.click(); send("cancel " + id); return true; }
                jy += 24;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (in(mx, my, listX(), listY(), listW(), listH())) { listScroll.wheel(sy, ROW_H); return true; }
        if (sideW > 0 && in(mx, my, sideX(), listY(), sideW, h)) { sideScroll.wheel(sy, UiStyle.LINE * 2); return true; }
        // a number box: the wheel steps it (shift: by ten)
        for (int i = 0; i < boxes.size() && chosen != null; i++) {
            EditBox b = boxes.get(i);
            if (!b.isMouseOver(mx, my)) continue;
            try {
                int step = hasShiftDown() ? 10 : 1;
                b.setValue(Integer.toString(Integer.parseInt(b.getValue().trim()) + (sy > 0 ? step : -step)));
            } catch (NumberFormatException ignored) {
                // not a number box
            }
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (modal != null) { modal = null; return true; }
            if (townsOpen) { townsOpen = false; return true; }
        }
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && chosen != null && modal == null && !search.isFocused()) {
            run(false);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    protected void setInitialFocus() {
        addRenderableWidget(search);
        setFocused(search);
    }

    @Override
    public void resize(Minecraft mc, int width, int height) {
        AdminSpec.Action keep = chosen;
        super.resize(mc, width, height);
        choose(keep);
    }
}
