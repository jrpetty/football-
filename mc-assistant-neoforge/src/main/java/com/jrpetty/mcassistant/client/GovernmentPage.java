package com.jrpetty.mcassistant.client;

import com.jrpetty.mcassistant.net.GovNetwork;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * [featcivic] The Government page of the town's books (CityScreen, the Civic section): how the town is governed, in seven
 * tabs (GovDraw): the overview (the form, how it works here, what it is good and bad at, what you can do here), the policy
 * the leader's personality sets, the election (who stands, the queue at the box, the last count as a bar chart), the
 * parties and the council's seats, the unrest meter and the petition, every form compared, and the neighbours'
 * governments. "Town Hall" opens the screen where a citizen votes, stands and signs; "Show me" marks the polling station
 * and the speech spot in the world.
 */
public final class GovernmentPage {

    private GovernmentPage() {}

    private static String tab = "Overview";
    private static int height;

    @Nullable
    public static List<Component> draw(GuiGraphics g, Font font, CompoundTag d, int x, int y, int w, int h, int scroll, int mx, int my,
                                       EarningsPage.Clicks clicks) {
        List<Component> tip = new ArrayList<>();
        // The tabs, and the Town Hall button on the right.
        int tx = x;
        String hall = "Town Hall";
        int hw = font.width(hall) + 12;
        for (String t : GovDraw.TABS) {
            int tw = font.width(t) + 10;
            if (tx + tw > x + w - hw - 6) break;
            boolean on = t.equals(tab), over = mx >= tx && mx < tx + tw && my >= y && my < y + 14;
            UiStyle.topTab(g, tx, y, tw, 14, UiStyle.State.of(true, on, over));
            g.drawString(font, t, tx + 5, y + 3, on ? UiStyle.ACCENT : UiStyle.TEXT_MUTED, false);
            final String pick = t;
            clicks.add(tx, y, tx + tw, y + 14, () -> { tab = pick; UiStyle.click(); });
            tx += tw + 1;
        }
        boolean overHall = mx >= x + w - hw && mx < x + w && my >= y && my < y + 14;
        UiStyle.button(g, font, hall, x + w - hw, y, hw, 14, UiStyle.State.of(true, false, overHall));
        clicks.add(x + w - hw, y, x + w, y + 14, () -> { UiStyle.click(); GovNetwork.act("open", GovDraw.args("tab", "Election".equals(tab) ? "ballot" : tab)); });
        if (overHall) tip.add(Component.literal("Open the Town Hall: vote, stand for office, sign a petition."));
        int top = y + 18, bodyH = h - 18;
        int offset = Math.max(0, Math.min(scroll * 10, Math.max(0, height - bodyH)));
        g.enableScissor(x, top, x + w, top + bodyH);
        List<Component> t2 = new ArrayList<>();
        height = GovDraw.section(g, font, d, tab, x + 2, top, w - 8, bodyH, offset, mx, my, clicks, GovernmentPage::act, false, t2);
        g.disableScissor();
        if (height > bodyH) UiStyle.scrollBar(g, x + w - 4, top, bodyH, offset, height - bodyH, bodyH);
        tip.addAll(t2);
        return tip.isEmpty() ? null : tip;
    }

    /** For the pictures (CineDev): the tab shown. */
    static void devTab(String t) {
        for (String x : GovDraw.TABS) if (x.equalsIgnoreCase(t)) tab = x;
    }

    /** A press on the page: a mark in the world and the petition go straight to the server; the rest open the Town Hall. */
    static void act(String action, CompoundTag args) {
        switch (action) {
            case "show", "sign" -> GovNetwork.act(action, args);
            case "pick" -> GovNetwork.act("open", GovDraw.args("tab", "ballot"));
            default -> GovNetwork.act("open", GovDraw.args("tab", "Election"));
        }
    }
}
