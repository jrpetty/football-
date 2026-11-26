package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.GovNetwork;
import com.jrpetty.mcassistant.pa.PaClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/**
 * [featcivic] The Town Hall: the town's government on one screen, and the ballot. Opened at the ballot box (the town's
 * polling station), from the board's Government page, or by asking a folk how the town is run. The tabs are the board's
 * (GovDraw) with the Ballot first: the candidates on a ballot paper, each with what it stands for and promises, a Vote
 * button on each (it says why when you may not vote), and a second click to confirm (a ballot cannot be changed). The
 * Election tab is where a citizen stands, promises, makes a speech or withdraws (confirmed too), and "Show me" marks the
 * polling station and the speech spot in the world. The Unrest tab has the petition to sign. The strip along the foot
 * says what the last click did, why, and what comes next. The first time it opens, a card says what it is for.
 */
public class GovScreen extends Screen {

    private static final String[] TABS = { "Ballot", "Overview", "Policy", "Election", "Parties", "Unrest", "Forms", "Relations" };
    private static final String HINT = "gov";

    private CompoundTag data;
    private String tab = "Overview";
    private int scroll, height0, left, top, w, h;
    private final List<Zone> zones = new ArrayList<>();
    private record Zone(int x0, int y0, int x1, int y1, Runnable act) {}
    /** A press waiting for its second click: "vote" (args: candidate, name) or "withdraw". */
    private String confirming = "";
    private CompoundTag confirmArgs = new CompoundTag();

    public GovScreen(CompoundTag data) {
        super(Component.literal("Town Hall"));
        this.data = data;
        this.tab = tabNamed(data.getString("tab"));
    }

    /** The server's page: shown, or the open screen brought up to date (keeping its tab unless the server asks for another). */
    public static void show(CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (mc.screen instanceof GovScreen open) {
            String keep = open.tab;
            open.data = data;
            open.tab = data.getString("tab").isEmpty() ? keep : tabNamed(data.getString("tab"));
            open.confirming = "";
            return;
        }
        mc.setScreen(new GovScreen(data));
    }

    static String tabNamed(String s) {
        for (String t : TABS) if (t.equalsIgnoreCase(s)) return t;
        return "Overview";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void layout() {
        w = Math.min(width - 16, 470);
        h = Math.min(height - 16, 320);
        left = (width - w) / 2;
        top = (height - h) / 2;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        layout();
        zones.clear();
        renderTransparentBackground(g);
        UiStyle.shadow(g, left, top, w, h);
        UiStyle.panel(g, left, top, w, h);
        UiStyle.headerBand(g, left, top, w, 26);
        String town = data.getString("town");
        UiStyle.title(g, font, UiStyle.clip(font, "Town Hall · " + town, w - 150), left + 8, top + 5);
        String sub = data.getString("formWords") + (data.getString("leader").isEmpty() ? "" : " · " + data.getString("title") + " " + data.getString("leader"));
        UiStyle.text(g, font, sub, left + 8, top + 15, w - 150, UiStyle.TEXT_MUTED);
        int cx = left + w - 8 - (font.width("Close") + 10);
        button(g, "Close", cx, top + 5, font.width("Close") + 10, 16, mx, my, true, "Close the Town Hall", this::onClose);
        int bx = cx - 4 - (font.width("Board") + 10);
        button(g, "Board", bx, top + 5, font.width("Board") + 10, 16, mx, my, true, "The town's books, at the Government page",
            () -> GovNetwork.act("books", new CompoundTag()));
        // The tabs.
        int tx = left + 6, ty = top + 30;
        for (String t : TABS) {
            int tw = font.width(t) + 10;
            if (tx + tw > left + w - 6) break;
            boolean on = t.equals(tab), over = in(mx, my, tx, ty, tw, 15);
            UiStyle.topTab(g, tx, ty, tw, 15, UiStyle.State.of(true, on, over));
            g.drawString(font, t, tx + 5, ty + 4, on ? UiStyle.ACCENT : UiStyle.TEXT_MUTED, false);
            final String pick = t;
            zones.add(new Zone(tx, ty, tx + tw, ty + 15, () -> { tab = pick; scroll = 0; confirming = ""; UiStyle.click(); }));
            tx += tw + 1;
        }
        int bodyTop = ty + 19, footH = 30, bodyH = h - (bodyTop - top) - footH - 4;
        int bodyX = left + 8, bodyW = w - 20;
        List<Component> tip = new ArrayList<>();
        if (!hintSeen()) {
            hint(g, bodyX, bodyTop, bodyW, mx, my);
        } else {
            int offset = Math.max(0, Math.min(scroll, Math.max(0, height0 - bodyH)));
            scroll = offset;
            g.enableScissor(bodyX - 2, bodyTop, bodyX + bodyW + 6, bodyTop + bodyH);
            height0 = GovDraw.section(g, font, data, tab, bodyX, bodyTop, bodyW, bodyH, offset, mx, my,
                (a, b, c, d, act) -> zones.add(new Zone(a, b, c, d, act)), this::act, true, tip);
            g.disableScissor();
            if (height0 > bodyH) UiStyle.scrollBar(g, left + w - 7, bodyTop, bodyH, offset, height0 - bodyH, bodyH);
        }
        foot(g, left + 6, top + h - footH - 4, w - 12, footH, mx, my);
        if (!tip.isEmpty()) UiStyle.componentTooltip(g, font, tip, mx, my);
        super.render(g, mx, my, pt);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        // drawn in render (the panel over a dimmed world)
    }

    /** The strip along the foot: what the last press did; or the confirm for a vote or a withdrawal; or what to do here. */
    private void foot(GuiGraphics g, int x, int y, int fw, int fh, int mx, int my) {
        UiStyle.well(g, x, y, fw, fh);
        if (!confirming.isEmpty()) {
            String q = confirming.equals("vote") ? "Cast your ballot for " + confirmArgs.getString("name") + "? It is secret, and cannot be changed."
                : "Withdraw from the election? Your name comes off the ballot.";
            UiStyle.text(g, font, q, x + 6, y + 4, fw - 150, UiStyle.TEXT);
            int bw = font.width("Confirm") + 12;
            button(g, "Confirm", x + fw - bw - 66, y + 7, bw, 16, mx, my, true, "Yes, do it", () -> {
                String a = confirming.equals("vote") ? "vote" : "withdraw";
                CompoundTag args = confirmArgs.copy();
                args.putString("tab", confirming.equals("vote") ? "Ballot" : "Election");
                confirming = "";
                Waiting.start(a.equals("vote") ? "Casting your ballot" : "Withdrawing");
                GovNetwork.act(a, args);
            });
            button(g, "Cancel", x + fw - 60, y + 7, 54, 16, mx, my, true, "Change your mind", () -> confirming = "");
            return;
        }
        String said = data.getString("said");
        int ink = switch (data.getString("saidKind")) {
            case "bad" -> UiStyle.DANGER;
            case "warn" -> UiStyle.WARNING;
            case "ok" -> UiStyle.SUCCESS;
            default -> UiStyle.TEXT_MUTED;
        };
        if (said.isEmpty()) {
            said = switch (tab) {
                case "Ballot" -> "Choose a candidate and press Vote, then Confirm. Citizens vote here or at the ballot box.";
                case "Election" -> "Show me marks the ballot box and the speech spot. Citizens can stand for office here.";
                case "Unrest" -> "Unrest builds from the town's grievances; a petition goes round at 45.";
                case "Forms" -> "Every form compared: hover a row for its strengths and weaknesses.";
                case "Relations" -> "How the neighbours are governed, and what it does to how you get on.";
                case "Policy" -> "What the leader's personality has made the town's policy.";
                case "Parties" -> "The parties, and the seats on the town council.";
                default -> "How " + data.getString("town") + " is governed, and what you can do here.";
            };
        }
        List<FormattedCharSequence> lines = TextCache.split(font, said, fw - 12);
        for (int i = 0; i < Math.min(2, lines.size()); i++) g.drawString(font, lines.get(i), x + 6, y + 5 + i * 10, ink, false);
        if (lines.size() > 2 && in(mx, my, x, y, fw, fh)) UiStyle.tooltip(g, font, said, mx, my, 260);
    }

    /** A press from a section (GovDraw): a vote and a withdrawal wait for the second click; the rest go to the server. */
    private void act(String action, CompoundTag args) {
        switch (action) {
            case "pick" -> { confirming = "vote"; confirmArgs = args.copy(); }
            case "confirmWithdraw" -> { confirming = "withdraw"; confirmArgs = new CompoundTag(); }
            default -> {
                CompoundTag a = args.copy();
                a.putString("tab", tab);
                if (!action.equals("show")) Waiting.start("One moment");
                GovNetwork.act(action, a);
            }
        }
    }

    // ------------------------------------------------------------------ the first-time hint

    private boolean hintSeen() {
        return ("," + PaClientConfig.get(PaClientConfig.KITHINTS, "") + ",").contains("," + HINT + ",");
    }

    private void hint(GuiGraphics g, int x, int y, int hw, int mx, int my) {
        String[] lines = {
            "This is the town's government: who leads, how they got there, and what it means for you here.",
            "Ballot: when an election is on, a citizen votes here or at the ballot box. Vote, then Confirm.",
            "Election: stand for office, make a speech; Show me marks the polling station and the speech spot.",
            "Unrest and Forms: what the town thinks of how it is run, and how every form of government compares." };
        int hh = 26;
        for (String l : lines) hh += TextCache.split(font, l, hw - 30).size() * 10 + 2;
        UiStyle.well(g, x, y, hw, hh + 24, UiStyle.HIGHLIGHT);
        g.fill(x, y, x + 3, y + hh + 24, UiStyle.HIGHLIGHT_SPINE);
        UiStyle.title(g, font, "The Town Hall", x + 8, y + 6);
        int ly = y + 20;
        for (int i = 0; i < lines.length; i++) {
            for (FormattedCharSequence s : TextCache.split(font, (i + 1) + ". " + lines[i], hw - 30)) {
                g.drawString(font, s, x + 10, ly, UiStyle.TEXT, false);
                ly += 10;
            }
            ly += 2;
        }
        button(g, "Got it", x + hw - 60, ly + 4, 52, 16, mx, my, true, "Don't show this again (the Guide has it: press the Guide key, search \"election\")", () -> {
            String seen = PaClientConfig.get(PaClientConfig.KITHINTS, "");
            PaClientConfig.set(PaClientConfig.KITHINTS, seen.isEmpty() ? HINT : seen + "," + HINT);
        });
    }

    // ------------------------------------------------------------------ input

    private void button(GuiGraphics g, String label, int x, int y, int bw, int bh, int mx, int my, boolean enabled, String why, Runnable act) {
        boolean over = in(mx, my, x, y, bw, bh);
        UiStyle.button(g, font, label, x, y, bw, bh, UiStyle.State.of(enabled, false, over));
        if (over) {
            g.pose().pushPose();
            g.pose().translate(0, 0, 400);
            UiStyle.tooltip(g, font, why, mx, my, 220);
            g.pose().popPose();
        }
        if (enabled) zones.add(new Zone(x, y, x + bw, y + bh, () -> { UiStyle.click(); act.run(); }));
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = zones.size() - 1; i >= 0; i--) {
                Zone z = zones.get(i);
                if (mx >= z.x0 && mx < z.x1 && my >= z.y0 && my < z.y1) {
                    z.act.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        scroll = Math.max(0, scroll - (int) Math.signum(dy) * 20);
        return true;
    }
}
